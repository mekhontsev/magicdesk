#ifndef MD_NETWORK_PACKET_H
#define MD_NETWORK_PACKET_H
#include <stdint.h>
#include <linux/if_packet.h>
#include "pid_namespace.h"

/* Internal datagram envelope. Payload remains an ordinary protocol message;
 * source group is independent of payload truncation and concurrent readers. */
struct md_network_packet {
    uint32_t group, port;
    int32_t pid;
    uint32_t uid, gid, pid_count;
    /* Message-time numbers survive sender exit/reaping and PID reuse. */
    struct md_pid_number_value pids[MD_PID_LEVELS];
    union {
        struct {
            struct sockaddr_ll ethernet;
            struct tpacket_auxdata auxiliary;
        };
        struct {
            unsigned char source[16];
            union {
                unsigned char destination[16];
                struct { uint32_t destination4, local4; };
            };
            unsigned interface;
            int hops;
            int64_t seconds, microseconds;
        } ip;
    };
};
#define MD_NETWORK_PACKET_MAX 65536
#define MD_NETWORK_BUFFER_SIZE 65536
/* Large control batches borrow adjacent adapter slots; ordinary packets and
 * every per-thread frame keep their existing size. */
#define MD_NETWORK_CONTROL_MAX (1024*1024)
struct md_network_send {
    uint32_t size;
    _Alignas(uint64_t) unsigned char value[128];
    uint32_t control_size;
    _Alignas(uint64_t) unsigned char control[256];
};
struct md_network_request {
    struct md_network_send destination;
    _Alignas(uint64_t) unsigned char data[MD_NETWORK_BUFFER_SIZE];
};
enum { MD_NETWORK_PACKET_INFO=1, MD_NETWORK_PACKET_CREDENTIALS=2, MD_NETWORK_PACKET_ETHERNET=4,
    MD_NETWORK_PACKET_IPV6=8, MD_NETWORK_PACKET_IPV6_INFO=16, MD_NETWORK_PACKET_IPV6_HOPS=32,
    MD_NETWORK_PACKET_TIMESTAMP=64, MD_NETWORK_PACKET_IPV4=128,
    MD_NETWORK_PACKET_IPV4_INFO=256, MD_NETWORK_PACKET_IPV4_TTL=512,
    MD_NETWORK_PACKET_AUXDATA=1024 };
long md_network_packet_receive(long,const unsigned long *);
#endif
