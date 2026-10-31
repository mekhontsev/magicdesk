#include "network_virtio.h"
#include <errno.h>
#include <linux/virtio_net.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

static unsigned be16(const unsigned char *p) { return (unsigned)p[0]<<8|p[1]; }
static unsigned field(const unsigned char *p,int le) { return le ? (unsigned)p[1]<<8|p[0] : be16(p); }
static void put16(unsigned char *p,unsigned value) { p[0]=(unsigned char)(value>>8); p[1]=(unsigned char)value; }
static uint32_t sum_bytes(uint32_t sum,const unsigned char *p,size_t size) {
    for(size_t i=0;i<size;i+=2) sum+=(unsigned)p[i]<<8|(i+1<size ? p[i+1] : 0);
    return sum;
}
static unsigned checksum(uint32_t sum) {
    while(sum>>16) sum=(sum&0xffff)+(sum>>16);
    return (uint16_t)~sum;
}
static int segment(const unsigned char *h,int le,int ethernet,const unsigned char *data,size_t size,
        int (*receive)(void *,const void *,size_t),void *context) {
    unsigned kind=h[1]&~VIRTIO_NET_HDR_GSO_ECN,chunk=field(h+4,le);
    if(kind!=VIRTIO_NET_HDR_GSO_TCPV4 && kind!=VIRTIO_NET_HDR_GSO_TCPV6) return -EOPNOTSUPP;
    if(!chunk || !(h[0]&VIRTIO_NET_HDR_F_NEEDS_CSUM)) return -EINVAL;
    size_t ip=0;
    if(ethernet) {
        if(size<14) return -EINVAL;
        unsigned protocol=be16(data+12); ip=14;
        while(protocol==0x8100 || protocol==0x88a8) {
            if(size-ip<4) return -EINVAL;
            protocol=be16(data+ip+2); ip+=4;
        }
        if(protocol!=(kind==VIRTIO_NET_HDR_GSO_TCPV4 ? 0x0800U : 0x86ddU)) return -EINVAL;
    }
    int v6=kind==VIRTIO_NET_HDR_GSO_TCPV6;
    if(size-ip<(v6 ? 40U : 20U) || data[ip]>>4!=(v6 ? 6 : 4)) return -EINVAL;
    size_t tcp=ip+(v6 ? 40 : (data[ip]&15)*4);
    unsigned protocol=data[ip+(v6 ? 6 : 9)];
    if(v6) {
        if(be16(data+ip+4)!=size-ip-40) return -EINVAL;
        while(protocol==0 || protocol==60) {
            if(size-tcp<8) return -EINVAL;
            size_t bytes=((size_t)data[tcp+1]+1)*8;
            if(bytes>size-tcp) return -EINVAL;
            protocol=data[tcp]; tcp+=bytes;
        }
    } else {
        if(tcp-ip<20 || tcp>size || be16(data+ip+2)!=size-ip || (be16(data+ip+6)&0x3fff)) return -EINVAL;
    }
    if(protocol!=6) return -EOPNOTSUPP;
    if(size-tcp<20 || field(h+6,le)!=tcp || field(h+8,le)!=16) return -EINVAL;
    size_t head=tcp+(data[tcp+12]>>4)*4;
    if(head-tcp<20 || head>size || (data[tcp+13]&0x26)) return -EINVAL; /* SYN/RST/URG cannot be split. */
    unsigned char *packet=malloc(size); if(!packet) return -ENOMEM;
    memcpy(packet,data,head);
    uint32_t sequence=(uint32_t)be16(data+tcp+4)<<16|be16(data+tcp+6);
    unsigned id=v6 ? 0 : be16(data+ip+4),number=0;
    int error=0;
    size_t offset=0;
    do {
        size_t payload=size-head-offset;
        if(payload>chunk) payload=chunk;
        size_t total=head+payload,transport=total-tcp;
        memcpy(packet+head,data+head+offset,payload);
        uint32_t seq=sequence+(uint32_t)offset;
        put16(packet+tcp+4,seq>>16); put16(packet+tcp+6,seq);
        packet[tcp+13]=data[tcp+13];
        if(offset) packet[tcp+13]&=~0x80; /* CWR belongs to the first segment. */
        if(offset+payload<size-head) packet[tcp+13]&=~0x09; /* FIN/PSH belong to the last. */
        uint32_t sum;
        if(v6) {
            put16(packet+ip+4,(unsigned)(total-ip-40));
            sum=sum_bytes((uint32_t)transport+6,packet+ip+8,32);
        } else {
            put16(packet+ip+2,(unsigned)(total-ip)); put16(packet+ip+4,id+number);
            packet[ip+10]=packet[ip+11]=0;
            put16(packet+ip+10,checksum(sum_bytes(0,packet+ip,tcp-ip)));
            sum=sum_bytes((uint32_t)transport+6,packet+ip+12,8);
        }
        packet[tcp+16]=packet[tcp+17]=0;
        put16(packet+tcp+16,checksum(sum_bytes(sum,packet+tcp,transport)));
        error=receive(context,packet,total);
        offset+=payload; ++number;
    } while(!error && offset<size-head);
    free(packet); return error;
}
int md_network_virtio_receive(const unsigned char *h,int le,int ethernet,const void *data,size_t size,
        int (*receive)(void *,const void *,size_t),void *context) {
    if(h[0]&~(VIRTIO_NET_HDR_F_NEEDS_CSUM|VIRTIO_NET_HDR_F_DATA_VALID)) return -EINVAL;
    if(field(h+2,le)>size) return -EINVAL;
    if(h[1]!=VIRTIO_NET_HDR_GSO_NONE) return segment(h,le,ethernet,data,size,receive,context);
    if(!(h[0]&VIRTIO_NET_HDR_F_NEEDS_CSUM)) return receive(context,data,size);
    size_t start=field(h+6,le),at=start+field(h+8,le);
    if(at+2>size) return -EINVAL;
    unsigned char *copy=malloc(size); if(!copy) return -ENOMEM;
    memcpy(copy,data,size);
    /* The checksum field contains the partial pseudo-header sum. */
    unsigned check=checksum(sum_bytes(0,copy+start,size-start));
    if(!check) check=0xffff; /* Match CHECKSUM_PARTIAL completion, including UDP's reserved zero. */
    if((at-start)&1) check=(check>>8)|(check<<8);
    put16(copy+at,check);
    int error=receive(context,copy,size);
    free(copy); return error;
}
