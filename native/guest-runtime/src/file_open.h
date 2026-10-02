#ifndef MD_FILE_OPEN_H
#define MD_FILE_OPEN_H
#include <fcntl.h>

/* A FIFO pin is an authorized O_PATH descriptor, not an opened pipe endpoint.
 * Complete it in the calling task, outside the namespace worker/transaction. */
enum md_open_kind { MD_OPEN_READY, MD_OPEN_FIFO, MD_OPEN_PIPE };
struct md_open_completion {
    enum md_open_kind kind;
    /* Owned only for MD_OPEN_PIPE; a regular-file rendezvous mapping. */
    int control;
};
int md_complete_open(int, int, struct md_open_completion);
static inline int md_open_endpoint_flags(int flags) {
    return flags & ~(O_CREAT | O_EXCL | O_NOFOLLOW);
}
#endif
