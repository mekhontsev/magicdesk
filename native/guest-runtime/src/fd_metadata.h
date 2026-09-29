#ifndef MD_FD_METADATA_H
#define MD_FD_METADATA_H

/* Caller retains an O_PATH descriptor to the already-resolved object. These
 * operations use the caller's real credentials and never reopen its guest path. */
long md_fd_chmod(int fd, unsigned mode);
long md_fd_xattr(int fd, long operation, const unsigned long *arguments);

#endif
