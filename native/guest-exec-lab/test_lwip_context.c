#define _GNU_SOURCE
#include "lwip_context.h"
#include "lwip/etharp.h"
#include "lwip/ethip6.h"
#include "lwip/ip.h"
#include "lwip/netif.h"
#include "lwip/pbuf.h"
#include "lwip/tcp.h"
#include "lwip/timeouts.h"
#include "lwip/udp.h"
#include "netif/ethernet.h"
#include <assert.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static _Thread_local u32_t now_ms;
u32_t __wrap_sys_now(void) { return now_ms; }

struct packet {
    struct packet *next;
    size_t size;
    unsigned char data[];
};
struct node {
    struct md_lwip_context *context;
    struct netif netif;
    struct node *peer;
    struct packet *head, **tail;
    unsigned packets, udp_received, tcp_received, tcp_connected;
    unsigned char pattern;
    int ethernet;
    struct tcp_pcb *client, *server, *listener;
    struct udp_pcb *udp;
};
enum { UDP_BYTES=8192, TCP_BYTES=98304 };

static err_t output(struct netif *netif,struct pbuf *p) {
    struct node *n=netif->state, *peer=n->peer;
    struct packet *q=malloc(sizeof(*q)+p->tot_len);
    assert(q);
    *q=(struct packet){.size=p->tot_len};
    assert(pbuf_copy_partial(p,q->data,p->tot_len,0)==p->tot_len);
    *peer->tail=q; peer->tail=&q->next; ++n->packets;
    return ERR_OK;
}
static err_t output4(struct netif *n,struct pbuf *p,const ip4_addr_t *a) {
    (void)a; return output(n,p);
}
static err_t output6(struct netif *n,struct pbuf *p,const ip6_addr_t *a) {
    (void)a; return output(n,p);
}
static err_t init_netif(struct netif *netif) {
    struct node *n=netif->state;
    netif->name[0]='t'; netif->name[1]='n'; netif->mtu=1280;
    netif->hwaddr_len=6;
    netif->hwaddr[0]=2; netif->hwaddr[5]=(n< n->peer)?1:2;
    netif->flags=NETIF_FLAG_BROADCAST | NETIF_FLAG_IGMP | NETIF_FLAG_MLD6;
    if (n->ethernet) netif->flags|=NETIF_FLAG_ETHARP | NETIF_FLAG_ETHERNET;
    netif->output=n->ethernet?etharp_output:output4;
    netif->output_ip6=n->ethernet?ethip6_output:output6;
    netif->linkoutput=output;
    return ERR_OK;
}
static void node_new(struct node *n,unsigned address) {
    n->context=md_lwip_context_new(); assert(n->context);
    struct md_lwip_context *previous=md_lwip_enter(n->context);
    ip4_addr_t a,mask,gateway;
    IP4_ADDR(&a,10,23,0,address); IP4_ADDR(&mask,255,255,255,0); ip4_addr_set_zero(&gateway);
    assert(netif_add(&n->netif,&a,&mask,&gateway,n,init_netif,n->ethernet?ethernet_input:ip_input));
    netif_set_default(&n->netif);
    netif_create_ip6_linklocal_address(&n->netif,1);
    netif_ip6_addr_set_state(&n->netif,0,IP6_ADDR_PREFERRED);
    ip6_addr_t a6;
    assert(ip6addr_aton(address==1?"fd23::1":"fd23::2",&a6));
    netif_ip6_addr_set(&n->netif,1,&a6);
    netif_ip6_addr_set_state(&n->netif,1,IP6_ADDR_PREFERRED);
    netif_set_link_up(&n->netif); netif_set_up(&n->netif);
    md_lwip_leave(previous);
}
static void pair_new(struct node pair[2],int ethernet,unsigned char pattern) {
    memset(pair,0,2*sizeof(*pair));
    for (unsigned i=0;i<2;++i) {
        pair[i].peer=&pair[1-i]; pair[i].tail=&pair[i].head;
        pair[i].ethernet=ethernet; pair[i].pattern=pattern;
    }
    node_new(&pair[0],1); node_new(&pair[1],2);
}
static void pair_free(struct node pair[2]) {
    for (unsigned i=0;i<2;++i) {
        struct node *n=&pair[i];
        while(n->head) { struct packet *q=n->head; n->head=q->next; free(q); }
        md_lwip_context_free(n->context);
    }
    assert(!md_lwip_current);
}
static unsigned deliver(struct node *n) {
    unsigned count=0;
    struct md_lwip_context *previous=md_lwip_enter(n->context);
    while (n->head && count<256) {
        struct packet *q=n->head;
        n->head=q->next; if(!n->head) n->tail=&n->head;
        struct pbuf *p=pbuf_alloc(PBUF_RAW,q->size,PBUF_RAM); assert(p);
        assert(pbuf_take(p,q->data,q->size)==ERR_OK); free(q);
        assert(n->netif.input(p,&n->netif)==ERR_OK); ++count;
    }
    sys_check_timeouts(); md_lwip_leave(previous); return count;
}
static void pump(struct node *pairs,unsigned count) {
    for (unsigned iteration=0;iteration<512;++iteration) {
        unsigned delivered=0;
        for (unsigned i=0;i<count*2;++i) delivered+=deliver(&pairs[i]);
        if (!delivered) return;
    }
    assert(!"packet feedback loop");
}
static void check_data(const struct pbuf *p,unsigned char value) {
    for (;p;p=p->next) for (unsigned i=0;i<p->len;++i)
        assert(((const unsigned char *)p->payload)[i]==value);
}
static void udp_received(void *arg,struct udp_pcb *pcb,struct pbuf *p,const ip_addr_t *a,u16_t port) {
    struct node *n=arg; (void)pcb; (void)a;
    assert(port==40000 && p->tot_len==UDP_BYTES);
    check_data(p,n->pattern); ++n->udp_received; pbuf_free(p);
}
static void udp_start(struct node pair[2],int ipv6) {
    for (unsigned i=0;i<2;++i) {
        struct node *n=&pair[i];
        struct md_lwip_context *previous=md_lwip_enter(n->context);
        n->udp=udp_new_ip_type(ipv6?IPADDR_TYPE_V6:IPADDR_TYPE_V4); assert(n->udp);
        assert(udp_bind(n->udp,ipv6?IP6_ADDR_ANY:IP4_ADDR_ANY,40000)==ERR_OK);
        udp_recv(n->udp,udp_received,n); md_lwip_leave(previous);
    }
    struct md_lwip_context *previous=md_lwip_enter(pair[0].context);
    struct pbuf *p=pbuf_alloc(PBUF_TRANSPORT,UDP_BYTES,PBUF_RAM); assert(p);
    memset(p->payload,pair[0].pattern,p->len);
    ip_addr_t address;
    assert(ipaddr_aton(ipv6?"fd23::2":"10.23.0.2",&address));
    assert(udp_sendto(pair[0].udp,p,&address,40000)==ERR_OK);
    pbuf_free(p); md_lwip_leave(previous);
}
static void tcp_error(void *arg,err_t error) {
    (void)arg; fprintf(stderr,"unexpected TCP error %d\n",error); abort();
}
static err_t tcp_received(void *arg,struct tcp_pcb *pcb,struct pbuf *p,err_t error) {
    struct node *n=arg; assert(error==ERR_OK);
    if (!p) return ERR_OK;
    check_data(p,n->pattern); n->tcp_received+=p->tot_len;
    tcp_recved(pcb,p->tot_len); pbuf_free(p); return ERR_OK;
}
static err_t accepted(void *arg,struct tcp_pcb *pcb,err_t error) {
    struct node *n=arg; assert(error==ERR_OK && !n->server);
    n->server=pcb; tcp_arg(pcb,n); tcp_err(pcb,tcp_error); tcp_recv(pcb,tcp_received); return ERR_OK;
}
static err_t tcp_connected(void *arg,struct tcp_pcb *pcb,err_t error) {
    struct node *n=arg; assert(error==ERR_OK); n->tcp_connected=1;
    unsigned char buffer[8192]; memset(buffer,n->pattern,sizeof(buffer));
    for (unsigned i=0;i<TCP_BYTES/sizeof(buffer);++i)
        assert(tcp_write(pcb,buffer,sizeof(buffer),TCP_WRITE_FLAG_COPY)==ERR_OK);
    assert(tcp_output(pcb)==ERR_OK); return ERR_OK;
}
static void tcp_start(struct node pair[2],int ipv6) {
    struct node *server=&pair[1], *client=&pair[0];
    struct md_lwip_context *previous=md_lwip_enter(server->context);
    server->listener=tcp_new_ip_type(ipv6?IPADDR_TYPE_V6:IPADDR_TYPE_V4); assert(server->listener);
    assert(tcp_bind(server->listener,ipv6?IP6_ADDR_ANY:IP4_ADDR_ANY,40100)==ERR_OK);
    server->listener=tcp_listen(server->listener); assert(server->listener);
    tcp_arg(server->listener,server); tcp_accept(server->listener,accepted);
    md_lwip_leave(previous); previous=md_lwip_enter(client->context);
    client->client=tcp_new_ip_type(ipv6?IPADDR_TYPE_V6:IPADDR_TYPE_V4); assert(client->client);
    assert(tcp_bind(client->client,ipv6?IP6_ADDR_ANY:IP4_ADDR_ANY,40101)==ERR_OK);
    tcp_arg(client->client,client); tcp_err(client->client,tcp_error);
    ip_addr_t address; assert(ipaddr_aton(ipv6?"fd23::2":"10.23.0.2",&address));
    assert(tcp_connect(client->client,&address,40100,tcp_connected)==ERR_OK);
    md_lwip_leave(previous);
}
static void isolation(int ethernet,int ipv6) {
    struct node pairs[4];
    pair_new(pairs,ethernet,0x31); pair_new(pairs+2,ethernet,0xb2);
    udp_start(pairs,ipv6); udp_start(pairs+2,ipv6);
    tcp_start(pairs,ipv6); tcp_start(pairs+2,ipv6);
    for (unsigned i=0;i<100;++i) {
        pump(pairs,2); now_ms+=250;
        if (pairs[1].tcp_received==TCP_BYTES && pairs[3].tcp_received==TCP_BYTES &&
            pairs[1].udp_received && pairs[3].udp_received) break;
    }
    assert(pairs[1].udp_received==1 && pairs[3].udp_received==1);
    assert(pairs[0].tcp_connected && pairs[2].tcp_connected);
    assert(pairs[1].tcp_received==TCP_BYTES && pairs[3].tcp_received==TCP_BYTES);
    assert(pairs[0].packets>10 && pairs[2].packets>10);
    /* Destroy with live TCP PCBs, timers and caches; the adjacent stack survives. */
    pair_free(pairs);
    struct md_lwip_context *previous=md_lwip_enter(pairs[2].context);
    unsigned char value=0xb2;
    assert(tcp_write(pairs[2].client,&value,1,TCP_WRITE_FLAG_COPY)==ERR_OK);
    assert(tcp_output(pairs[2].client)==ERR_OK); md_lwip_leave(previous);
    for (unsigned i=0;i<16 && pairs[3].tcp_received!=TCP_BYTES+1;++i) {
        now_ms+=250; pump(pairs+2,1);
    }
    assert(pairs[3].tcp_received==TCP_BYTES+1);
    pair_free(pairs+2);
}
static void *worker(void *unused) {
    (void)unused;
    for (unsigned round=0;round<4;++round)
        for(int ethernet=0;ethernet<2;++ethernet) for(int ipv6=0;ipv6<2;++ipv6) isolation(ethernet,ipv6);
    return NULL;
}
int main(void) {
    struct md_lwip_context *limited=md_lwip_context_new(); assert(limited);
    struct md_lwip_context *previous=md_lwip_enter(limited);
    size_t initial=limited->bytes;
    assert(!md_lwip_malloc(MD_LWIP_MEMORY_LIMIT));
    void *allocation=md_lwip_malloc(MD_LWIP_MEMORY_LIMIT/2); assert(allocation);
    assert(!md_lwip_malloc(MD_LWIP_MEMORY_LIMIT/2));
    md_lwip_free(allocation); assert(limited->bytes==initial);
    md_lwip_leave(previous); md_lwip_context_free(limited);
    worker(NULL);
    pthread_t threads[4];
    for (unsigned i=0;i<4;++i) assert(!pthread_create(&threads[i],NULL,worker,NULL));
    for (unsigned i=0;i<4;++i) assert(!pthread_join(threads[i],NULL));
    puts("lwIP context: TCP/UDP IPv4/IPv6, raw IP/Ethernet, fragmentation, duplicate tuples, threaded isolation, retirement PASS");
    return 0;
}
