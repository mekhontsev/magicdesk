#ifndef MD_IMAGE_IO_H
#define MD_IMAGE_IO_H
#include <stdint.h>
#include <stddef.h>
int md_image_read(int, size_t, char **);
int md_image_write(int, const void *, size_t);
int md_image_hash(int, uint64_t, char [65]);
int md_image_blob(int, int, const char *, uint64_t, int *);
int md_image_snapshot(int source, int scratch, uint64_t limit, int *);
int md_image_digest_valid(const char *);
#endif
