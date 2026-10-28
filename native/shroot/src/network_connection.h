#ifndef MD_NETWORK_CONNECTION_H
#define MD_NETWORK_CONNECTION_H
#include "control_file.h"

/* Connect completion overlays native readiness without retaining the data OFD
 * or mediating reads/writes. The endpoint owns this for its entire lifetime. */
struct md_network_connection;
struct md_network_connection *md_network_connection_new(int);
void md_network_connection_free(struct md_network_connection *);
int md_network_connection_begin(struct md_network_connection *);
void md_network_connection_complete(struct md_network_connection *,int);
void md_network_connection_reset(struct md_network_connection *);
int md_network_connection_result(struct md_network_connection *,int *);
int md_network_connection_error(struct md_network_connection *,int *);
struct md_control_file md_network_connection_readiness(struct md_network_connection *);
#endif
