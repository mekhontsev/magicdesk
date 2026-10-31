#ifndef MD_NETWORK_FILTER_H
#define MD_NETWORK_FILTER_H
#include <stddef.h>
#include <stdint.h>
#include "network_ip.h"
#include "network_reject.h"

/* Namespace-owned ruleset catalogue. Mutations prepare a private snapshot;
 * publication and replacement run under the owning network fabric lock. */
struct md_network_filter;
struct md_network_filter_table {
    uint64_t handle;
    unsigned family;
    char name[256];
    size_t userdata_size;
    unsigned char userdata[256];
};
enum md_filter_opcode {
    MD_FILTER_IMMEDIATE, MD_FILTER_VERDICT, MD_FILTER_META, MD_FILTER_META_SET,
    MD_FILTER_PAYLOAD, MD_FILTER_COMPARE, MD_FILTER_RANGE, MD_FILTER_BITWISE,
    MD_FILTER_CT, MD_FILTER_CT_SET, MD_FILTER_FIB, MD_FILTER_LOOKUP,
    MD_FILTER_NAT, MD_FILTER_MASQUERADE, MD_FILTER_VERDICT_MAP, MD_FILTER_DATA_MAP, MD_FILTER_COUNTER,
    MD_FILTER_TRANSPORT, MD_FILTER_STATE_MATCH, MD_FILTER_MARK_MATCH, MD_FILTER_ADDRESS_TYPE,
    MD_FILTER_PACKET_TYPE, MD_FILTER_REVERSE_PATH, MD_FILTER_ICMP_MATCH, MD_FILTER_REJECT
};
struct md_filter_counter { uint64_t packets, bytes; };
enum { MD_FILTER_INVERT_SOURCE=1, MD_FILTER_INVERT_DESTINATION=2 };
struct md_filter_transport {
    uint16_t source[2], destination[2]; /* Inclusive host-order port bounds. */
    unsigned protocol, inversion;
};
static inline int md_filter_port_matches(unsigned port,const uint16_t bounds[2],int invert) {
    return (port>=bounds[0] && port<=bounds[1])!=!!invert;
}
/* Borrowed counter cells are valid only under the fabric lock and until the
 * policy generation changes. Flow owners rebuild this plan before using it. */
struct md_filter_accounting { struct md_filter_counter **cells; size_t count, capacity; };
struct md_filter_expression {
    enum md_filter_opcode code;
    unsigned src, dst, size, key, operation, aux;
    unsigned constant, family; /* NAT address/port in value instead of registers. */
    unsigned port_range, port_max; /* Maximum register offset, or network-order constant. */
    uint64_t target;
    unsigned char value[16], mask[16];
    union {
        struct md_filter_counter counter;
        struct md_filter_transport transport;
        uint16_t nat_port;
    };
};
static inline int md_filter_set_expression(const struct md_filter_expression *e) {
    return e->code==MD_FILTER_LOOKUP || e->code==MD_FILTER_VERDICT_MAP || e->code==MD_FILTER_DATA_MAP;
}
struct md_filter_chain {
    uint64_t table, handle;
    uint32_t id;
    char name[256];
    int hook, priority, nat;
    unsigned policy;
    unsigned counters;
    struct md_filter_counter counter;
    size_t userdata_size;
    unsigned char userdata[256];
};
struct md_filter_rule {
    uint64_t chain, handle;
    uint32_t id;
    unsigned count;
    struct md_filter_expression expressions[64];
    void *wire;
    size_t wire_size, userdata_size;
    unsigned char userdata[256];
};
struct md_filter_verdict { unsigned operation; uint64_t target; };
struct md_filter_element {
    unsigned char key[16];
    unsigned end;
    union { struct md_filter_verdict verdict; unsigned char data[64]; };
};
struct md_filter_set {
    uint64_t table, handle;
    uint32_t id, flags, key_type, key_size, data_type, data_size;
    char name[256];
    struct md_filter_element *elements;
    size_t count, capacity, userdata_size;
    unsigned char userdata[256];
};
struct md_filter_route_result { unsigned type, interface; char name[16]; };
/* Flow coordinates are network byte order; register metadata is native byte
 * order, as in the nftables expression ABI. Callers own route/conntrack state. */
struct md_filter_packet {
    struct md_ip_address source, destination;
    uint16_t source_port, destination_port;
    unsigned protocol, state, mark, ctmark, status;
    unsigned input, output, fib_type, source_fib_type;
    unsigned packet_type;
    struct md_network_rejection rejection;
    unsigned char transport[4], transport_size;
    char input_name[16], output_name[16];
    unsigned snat, dnat, masquerade;
    struct md_ip_address masquerade_address;
    struct md_filter_accounting *accounting;
    unsigned unmediated; /* Admission probe: reject mutations, skip counters. */
    void *route_context;
    struct md_filter_route_result (*route)(void *,const struct md_filter_packet *,unsigned);
    int (*source_nat)(void *,struct md_filter_packet *,uint16_t,uint16_t);
};
struct md_filter_rewrite {
    struct md_ip_address source, destination;
    uint32_t fib_type, source_fib_type;
    uint16_t source_port, destination_port;
    int priority;
};
struct md_filter_port { struct md_ip_address address; uint16_t port; unsigned protocol; };
struct md_network_filter {
    uint32_t generation;
    uint64_t next_handle;
    struct md_network_filter_table *tables;
    size_t count, capacity;
    struct md_filter_chain *chains;
    size_t chain_count, chain_capacity;
    struct md_filter_rule *rules;
    size_t rule_count, rule_capacity;
    struct md_filter_set *sets;
    size_t set_count, set_capacity;
};
uint32_t md_network_filter_generation(const struct md_network_filter *);
uint32_t md_network_filter_next_generation(const struct md_network_filter *);
struct md_network_filter *md_network_filter_copy(const struct md_network_filter *);
void md_network_filter_free(struct md_network_filter *);
struct md_network_filter_table *md_network_filter_find(struct md_network_filter *,unsigned,const char *,uint64_t);
int md_network_filter_add(struct md_network_filter *,unsigned,const char *,const void *,size_t);
void md_network_filter_delete(struct md_network_filter *,unsigned,const char *,uint64_t);
int md_filter_reserve(void **,size_t *,size_t,size_t,size_t);
struct md_filter_chain *md_filter_chain_find(struct md_network_filter *,uint64_t,const char *,uint64_t,uint32_t);
struct md_filter_set *md_filter_set_find(struct md_network_filter *,uint64_t,const char *,uint64_t,uint32_t);
size_t md_filter_set_position(const struct md_filter_set *,const void *,unsigned end);
const struct md_filter_element *md_filter_set_lookup(const struct md_filter_set *,const void *);
void md_filter_rule_delete(struct md_network_filter *,size_t);
void md_filter_chain_delete(struct md_network_filter *,size_t);
void md_filter_set_delete(struct md_network_filter *,size_t);
int md_filter_validate(const struct md_network_filter *);
size_t md_filter_verdict_count(const struct md_network_filter *,const struct md_filter_expression *);
struct md_filter_verdict md_filter_verdict_at(const struct md_network_filter *,const struct md_filter_expression *,size_t);
int md_filter_evaluate(struct md_network_filter *,unsigned,struct md_filter_packet *);
int md_filter_evaluate_transition(struct md_network_filter *,unsigned,struct md_filter_packet *,
    const struct md_filter_rewrite *,int *);
void md_filter_account(struct md_filter_accounting *,size_t);
int md_filter_chain_applies(const struct md_network_filter *,const struct md_filter_chain *,unsigned);
int md_filter_ports(struct md_network_filter *,struct md_ip_address,struct md_filter_port *,size_t,size_t *);
#endif
