#ifndef MD_IMAGE_CATALOGUE_H
#define MD_IMAGE_CATALOGUE_H
#include "inode_store.h"

/* Launch-scoped immutable image view; the persistent inode store is unchanged. */
struct md_image_catalogue;
int md_image_catalogue_open(struct md_inode_store *, const char *, struct md_image_catalogue **);
void md_image_catalogue_close(struct md_image_catalogue *);
int md_catalogue_open(struct md_image_catalogue *, struct md_inode_store *, int, const char *, int, mode_t, uint64_t);
int md_catalogue_open_object(struct md_image_catalogue *, struct md_inode_store *, const char *, int);
int md_catalogue_object_id(struct md_image_catalogue *, struct md_inode_store *, int, char [33]);
int md_catalogue_path(struct md_image_catalogue *, struct md_inode_store *, int, char *, size_t);
int md_catalogue_stat(struct md_image_catalogue *, struct md_inode_store *, int, const char *, int, struct stat *);
int md_catalogue_fstat(struct md_image_catalogue *, struct md_inode_store *, int, struct stat *);
#endif
