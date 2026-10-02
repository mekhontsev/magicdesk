#ifndef MD_IMAGE_POOL_H
#define MD_IMAGE_POOL_H
#include <stdint.h>
struct md_image_pool { int root, objects; };
/* fd is a private, digest-verified uncompressed layer. Pools contain bytes and
 * native data attributes only, never guest names or a chain of namespaces. */
int md_image_pool_open(const char *directory, const char *diff_id, int fd, struct md_image_pool *);
void md_image_pool_close(struct md_image_pool *);
void md_image_pool_name(uint64_t entry, char name[33]);
#endif
