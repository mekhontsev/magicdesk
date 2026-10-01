#ifndef MD_IMAGE_LAYER_H
#define MD_IMAGE_LAYER_H
#include "inode_store.h"
/* Offline, already digest-checked, uncompressed tar. All names are guest names. */
int md_image_layer(struct md_inode_store *, int, uint64_t *, uint64_t *);
int md_image_layers_finish(struct md_inode_store *);
#endif
