#ifndef MD_NETWORK_VIRTIO_H
#define MD_NETWORK_VIRTIO_H
#include <stddef.h>

/* Decode the ten-byte virtio-net header before packet ingress. Ordinary packets
 * are borrowed; checksum completion and TCP segmentation use temporary storage. */
int md_network_virtio_receive(const unsigned char *header,int little_endian,int ethernet,
    const void *data,size_t size,int (*receive)(void *,const void *,size_t),void *context);
#endif
