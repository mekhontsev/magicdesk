#include "network_reject.h"
#include <errno.h>
#include <sys/socket.h>

int md_network_reject_valid(unsigned type,unsigned code) {
    switch(type) {
    case MD_REJECT_TCP: return code==0;
    case MD_REJECT_ICMP4: return code<=3 || code==9 || code==10 || code==13;
    case MD_REJECT_ICMP6: return code<=6;
    case MD_REJECT_ICMPX: return code<=3;
    default: return 0;
    }
}
struct md_network_rejection md_network_reject_resolve(unsigned type,unsigned code,unsigned family) {
    if(type==MD_REJECT_ICMPX) {
        static const unsigned char v4[]={0,3,1,13},v6[]={0,4,3,1};
        if(code>3) return (struct md_network_rejection){0};
        code=family==AF_INET6 ? v6[code] : v4[code];
        type=family==AF_INET6 ? MD_REJECT_ICMP6 : MD_REJECT_ICMP4;
    }
    if(!md_network_reject_valid(type,code)
            || (type==MD_REJECT_ICMP4 && family!=AF_INET)
            || (type==MD_REJECT_ICMP6 && family!=AF_INET6)) return (struct md_network_rejection){0};
    return (struct md_network_rejection){.type=type,.code=code};
}
int md_network_reject_error(struct md_network_rejection r) {
    if(r.type==MD_REJECT_TCP) return -ECONNREFUSED;
    if(r.type==MD_REJECT_ICMP4) {
        switch(r.code) {
        case 0: return -ENETUNREACH;
        case 1: return -EHOSTUNREACH;
        case 2: return -ENOPROTOOPT;
        case 3: return -ECONNREFUSED;
        case 9: return -ENETUNREACH;
        case 10: return -EHOSTUNREACH;
        case 13: return -EACCES;
        }
    } else if(r.type==MD_REJECT_ICMP6) {
        switch(r.code) {
        case 0: return -ENETUNREACH;
        case 1: case 5: return -EACCES;
        case 2: case 3: return -EHOSTUNREACH;
        case 4: return -ECONNREFUSED;
        case 6: return -EACCES;
        }
    }
    return -EOPNOTSUPP;
}
