#include "network_internal.h"
#include "network_forward.h"
#include "test_network_fixture.h"
#include <assert.h>
#include <errno.h>
#include <net/if.h>
#include <stdio.h>
#include <string.h>
#include <poll.h>
#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <unistd.h>

struct event_test { struct md_network_event_source *source; int input, done; unsigned count; };
static void event_ready(void *context,uint32_t events) {
    struct event_test *t=context;
    assert(events&EPOLLIN);
    uint64_t value; assert(read(t->input,&value,sizeof(value))==sizeof(value));
    ++t->count;
    md_network_event_remove(t->source); t->source=NULL;
    assert(write(t->done,&value,sizeof(value))==sizeof(value));
}
static void events(void) {
    struct md_network_namespace *n=md_network_namespace_new(NULL,MD_NETWORK_PRIVATE); assert(n);
    struct event_test t={.input=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK),.done=eventfd(0,EFD_CLOEXEC|EFD_NONBLOCK)};
    assert(t.input>=0 && t.done>=0);
    for (unsigned i=0;i<32;++i) {
        pthread_mutex_lock(&n->fabric->lock);
        assert(!md_network_event_add(n->fabric,t.input,0,event_ready,&t,&t.source));
        uint64_t one=1; assert(write(t.input,&one,sizeof(one))==sizeof(one));
        assert(!md_network_event_update(t.source,EPOLLIN));
        pthread_mutex_unlock(&n->fabric->lock);
        /* EVENT_WAIT: exact callback completion; timeout is a fixture failure. */
        struct pollfd p={.fd=t.done,.events=POLLIN}; assert(poll(&p,1,2000)==1);
        assert(read(t.done,&one,sizeof(one))==sizeof(one));
        pthread_mutex_lock(&n->fabric->lock);
        assert(!t.source && t.count==i+1);
        pthread_mutex_unlock(&n->fabric->lock);
    }
    md_network_namespace_release(n); close(t.input); close(t.done);
    puts("PASS network event sources: shared loop, readiness update, callback retirement and shutdown");
}

void test_network_model(void) {
    events();
    struct md_network_namespace *root=md_network_namespace_new(NULL,MD_NETWORK_ROUTED);
    assert(root && root->count==2 && test_network_default(root,AF_INET) && test_network_default(root,AF_INET6));
    struct md_network_namespace *child=md_network_namespace_copy(root,NULL);
    struct md_network_namespace *foreign=md_network_namespace_new(NULL,MD_NETWORK_PRIVATE);
    assert(child && foreign && child->fabric==root->fabric && foreign->fabric!=root->fabric);
    struct md_fd_object *routed_pair=md_network_socket_new(root);
    struct md_fd_object *offline_pair=md_network_socket_new(foreign);
    assert(routed_pair && offline_pair);
    assert(!md_network_socket_offline(routed_pair));
    assert(md_network_socket_offline(offline_pair));
    md_fd_object_release(routed_pair); md_fd_object_release(offline_pair);
    assert(child->count==1 && !child->route_count && !child->links[0].address_count);
    unsigned first=0,second=0,bridge=0;
    pthread_mutex_lock(&root->fabric->lock);
    assert(!md_network_interface_add(root,MD_LINK_BRIDGE,"bridge0",&bridge));
    assert(!md_network_interface_add(root,MD_LINK_VETH,"host-end",&first));
    assert(!md_network_interface_add(root,MD_LINK_VETH,"guest-end",&second));
    assert(first!=second && second!=bridge && first!=bridge);
    struct md_network_interface *a=md_network_interface_find(root,first,NULL);
    struct md_network_interface *b=md_network_interface_find(root,second,NULL);
    a->peer=second; a->master=bridge; b->peer=first;
    assert(md_network_interface_move(root,second,foreign,"eth0")==-EXDEV);
    assert(md_network_interface_move(root,second,child,"lo")==-EEXIST);
    assert(md_network_interface_find(root,second,NULL));
    assert(!md_network_interface_move(root,second,child,"eth0"));
    assert(!md_network_interface_find(root,second,NULL));
    assert(md_network_interface_find(child,second,NULL)->peer==first);
    assert(md_network_interface_owner(root->fabric,second)==child);
    a=md_network_interface_find(root,first,NULL);
    b=md_network_interface_find(child,second,NULL);
    struct md_network_interface *master=md_network_interface_find(root,bridge,NULL);
    assert(md_network_interface_snapshot(root,a).state==IF_OPER_DOWN);
    a->info.flags|=IFF_UP; master->info.flags|=IFF_UP;
    assert(md_network_interface_snapshot(root,a).state==IF_OPER_LOWERLAYERDOWN);
    assert(!(md_network_interface_snapshot(root,a).flags&IFF_RUNNING));
    assert(md_network_interface_snapshot(root,master).state==IF_OPER_DOWN);
    b->info.flags|=IFF_UP;
    assert(md_network_interface_snapshot(root,a).state==IF_OPER_UP);
    assert(md_network_interface_snapshot(child,b).state==IF_OPER_UP);
    assert(md_network_interface_snapshot(root,master).state==IF_OPER_UP);
    assert(md_network_interface_snapshot(root,a).flags&IFF_RUNNING);
    b->info.flags&=~IFF_UP;
    assert(md_network_interface_snapshot(root,master).state==IF_OPER_DOWN);
    a->hairpin=a->isolated=1;
    assert(md_network_interface_add(root,MD_LINK_BRIDGE,"bad/name",&bridge)==-EINVAL);
    assert(md_network_interface_add(root,MD_LINK_BRIDGE,"bridge0",&bridge)==-EEXIST);
    assert(md_network_interface_remove(child,1)==-EOPNOTSUPP);
    assert(!md_network_interface_remove(root,bridge));
    a=md_network_interface_find(root,first,NULL);
    assert(!a->master);
    assert(!a->hairpin && !a->isolated);
    pthread_mutex_unlock(&root->fabric->lock);
    md_network_namespace_release(child);
    pthread_mutex_lock(&root->fabric->lock);
    assert(!md_network_interface_find(root,first,NULL) && root->count==2);
    pthread_mutex_unlock(&root->fabric->lock);
    child=md_network_namespace_copy(root,NULL); assert(child);
    md_network_namespace_release(root);
    struct md_network_link loopback;
    assert(!md_network_link_info(child,1,&loopback) && !strcmp(loopback.name,"lo"));
    md_network_namespace_release(child); md_network_namespace_release(foreign);
    puts("PASS network topology: private births, owned uplink, veth moves, collisions and peer teardown");
}
