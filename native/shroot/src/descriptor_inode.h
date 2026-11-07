#ifndef MD_DESCRIPTOR_INODE_H
#define MD_DESCRIPTOR_INODE_H
#include "fd_object.h"
#include "guest_identity.h"
#include <sys/stat.h>

/* Metadata only: no pipe endpoint/OFD is retained by this owner. Both ends,
 * duplicates and transferred descriptors retain the same logical inode. */
struct md_fd_object *md_descriptor_inode_new(const struct md_identity *, mode_t);
/* Adopt a launcher-owned anonymous pipe. Retains only its O_PATH identity,
 * never a reader/writer that could postpone EOF. Host metadata is unchanged. */
struct md_fd_object *md_descriptor_inode_inherit(const struct md_identity *, int pin, mode_t);
int md_descriptor_inode_pin(const struct md_fd_object *);
int md_descriptor_inode_is(const struct md_fd_object *);
int md_descriptor_inode_stat(struct md_fd_object *, const struct md_identity *, struct stat *);
int md_descriptor_inode_chown(struct md_fd_object *, const struct md_identity *, uint32_t, uint32_t);
int md_descriptor_inode_chmod(struct md_fd_object *, const struct md_identity *, mode_t);
int md_descriptor_inode_access(struct md_fd_object *, const struct md_identity *, int mode);
/* Cold authenticated custody. Export borrows the data FD only to bind identity;
 * import borrows both descriptors and opens its own metadata lock description.
 * Metadata survives sender exit; neither side retains a data endpoint. */
int md_descriptor_inode_export(struct md_fd_object *, int data_fd, int directory, int *out);
int md_descriptor_inode_import(int metadata_fd, int data_fd, struct md_fd_object **out);
#endif
